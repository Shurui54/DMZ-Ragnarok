package net.shurui.shuruisutilities.sparring;

import java.util.HashMap;
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
 * Crash-recovery record for the sparring module. A live spar is in-memory only, but it forces friendly-fist ON for
 * both fighters, which is a persisted DragonMineZ stat. If the server crashes mid-spar the in-memory session is
 * lost, so this SavedData remembers each active fighter's PRIOR friendly-fist flag; on their next login the module
 * restores it and clears the entry (see {@code ModuleSparring.onLogin}). A clean spar end removes the entry itself.
 *
 * <p>The SavedData id is {@code shuruisutilities_sparring} and must not change (it keys the file in the world save).
 */
public final class SparData extends SavedData
{
    private static final String NAME = "shuruisutilities_sparring";

    // uuid -> friendly-fist flag the fighter had before a spar forced it on
    private final Map<UUID, Boolean> priorFriendlyFist = new HashMap<>();

    public static SparData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(SparData::load, SparData::new, NAME);
    }

    public void put(UUID id, boolean priorFf)
    {
        priorFriendlyFist.put(id, priorFf);
        setDirty();
    }

    public boolean contains(UUID id)
    {
        return priorFriendlyFist.containsKey(id);
    }

    /** Prior friendly-fist flag for a recovering fighter (defaults false if absent). */
    public boolean priorFor(UUID id)
    {
        return priorFriendlyFist.getOrDefault(id, false);
    }

    public void remove(UUID id)
    {
        if (priorFriendlyFist.remove(id) != null)
            setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Boolean> e : priorFriendlyFist.entrySet())
        {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putBoolean("ff", e.getValue());
            list.add(entry);
        }
        tag.put("fighters", list);
        return tag;
    }

    public static SparData load(CompoundTag tag)
    {
        SparData data = new SparData();
        ListTag list = tag.getList("fighters", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag entry = list.getCompound(i);
            data.priorFriendlyFist.put(entry.getUUID("id"), entry.getBoolean("ff"));
        }
        return data;
    }
}
