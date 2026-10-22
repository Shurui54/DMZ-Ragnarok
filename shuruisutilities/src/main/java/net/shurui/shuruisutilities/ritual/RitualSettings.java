package net.shurui.shuruisutilities.ritual;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * In-game ritual settings, edited from the admin hub ("rituals" row, {@code /rg edit} and {@code /rggui}): how many
 * players must charge their ki around a Super Saiyan God ritual, and whether DragonMineZ's own god ritual may start. Saved with the overworld and carried to every
 * shard through the state sync ({@code su:ritual_settings}). 0 (the default, and every world that never saved one)
 * means "use the {@code SsgRitualRequiredChargers} config value", so nothing changes until an admin sets it.
 */
public class RitualSettings extends SavedData
{
    private static final String NAME = "shuruisutilities_ritual_settings";

    private int ssgRequiredChargers;

    /** DragonMineZ's own god ritual: 0 = the {@code DmzGodRitualEnabled} config value, 1 = on, 2 = off. */
    private int dmzGodRitual;

    public static RitualSettings get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(RitualSettings::load, RitualSettings::new, NAME);
    }

    /** The chargers an SSG ritual needs on this server: the in-game value, or the config value when none is set. */
    public static int ssgRequiredChargers(MinecraftServer server)
    {
        try
        {
            int set = server == null ? 0 : get(server).ssgRequiredChargers;
            if (set > 0)
                return set;
        }
        catch (Throwable ignored)
        {
        }
        return Math.max(1, SUConfig.ssgRitualRequiredChargers);
    }

    /** The stored value: 0 when the config value is in use. */
    public int storedSsgRequiredChargers()
    {
        return ssgRequiredChargers;
    }

    /** Set it (at least 1), or 0 to go back to the config value. */
    public void setSsgRequiredChargers(int value)
    {
        int v = Math.max(0, value);
        if (v != ssgRequiredChargers)
        {
            ssgRequiredChargers = v;
            setDirty();
        }
    }

    /** Whether DragonMineZ's own Super Saiyan God ritual may start: the in-game choice, else the config value. */
    public static boolean dmzGodRitualEnabled(MinecraftServer server)
    {
        try
        {
            int set = server == null ? 0 : get(server).dmzGodRitual;
            if (set == 1)
                return true;
            if (set == 2)
                return false;
        }
        catch (Throwable ignored)
        {
        }
        return SUConfig.dmzGodRitualEnabled;
    }

    /** The stored choice: 0 config value, 1 on, 2 off. */
    public int storedDmzGodRitual()
    {
        return dmzGodRitual;
    }

    public void setDmzGodRitual(int value)
    {
        int v = value == 1 || value == 2 ? value : 0;
        if (v != dmzGodRitual)
        {
            dmzGodRitual = v;
            setDirty();
        }
    }

    public static RitualSettings load(CompoundTag tag)
    {
        RitualSettings s = new RitualSettings();
        s.ssgRequiredChargers = Math.max(0, tag.getInt("ssgChargers"));
        s.dmzGodRitual = tag.getInt("dmzGodRitual");
        return s;
    }

    /** State sync write: into the live instance. A tag without the key is not a settings block and is ignored. */
    public void loadInto(CompoundTag tag)
    {
        if (tag == null || !tag.contains("ssgChargers"))
            return;
        setSsgRequiredChargers(tag.getInt("ssgChargers"));
        // a copy from a shard on an older build has no key: keep the local choice
        if (tag.contains("dmzGodRitual"))
            setDmzGodRitual(tag.getInt("dmzGodRitual"));
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putInt("ssgChargers", ssgRequiredChargers);
        // written only once it was ever set, so a world that never touched it saves byte-identical
        if (dmzGodRitual != 0)
            tag.putInt("dmzGodRitual", dmzGodRitual);
        return tag;
    }
}
