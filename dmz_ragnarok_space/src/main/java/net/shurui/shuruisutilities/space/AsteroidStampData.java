package net.shurui.shuruisutilities.space;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which asteroid CELLS have been stamped into real blocks, so each lump is placed once and never re-stamped over
 * player-mined damage. One persisted flag per cell key ({@link AsteroidPositions#cellKey}). The geometry is
 * re-derivable from {@link AsteroidPositions}, so this is the ONLY asteroid state persisted. On overworld storage (not
 * the space dimension) so it cannot be lost by a space chunk unloading.
 */
public final class AsteroidStampData extends SavedData
{
    private static final String NAME = "shuruisutilities_asteroid_stamps";

    private final Set<String> stamped = new HashSet<>();

    public static AsteroidStampData get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(AsteroidStampData::load, AsteroidStampData::new, NAME);
    }

    private static AsteroidStampData load(CompoundTag tag)
    {
        AsteroidStampData s = new AsteroidStampData();
        ListTag list = tag.getList("stamped", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++)
        {
            s.stamped.add(list.getString(i));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (String key : stamped)
        {
            list.add(StringTag.valueOf(key));
        }
        tag.put("stamped", list);
        return tag;
    }

    public boolean isStamped(String cellKey)
    {
        return stamped.contains(cellKey);
    }

    public void markStamped(String cellKey)
    {
        if (stamped.add(cellKey))
        {
            setDirty();
        }
    }

    // drop the flag when a lump is cleared out (its cell bumps generation). No-op if it was not stamped.
    public void clearStamped(String cellKey)
    {
        if (stamped.remove(cellKey))
        {
            setDirty();
        }
    }
}
