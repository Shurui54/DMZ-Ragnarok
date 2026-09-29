package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

// classload-safe entry point for the Deletion Wand's DMZ master checks. NO DMZ imports here: every method
// checks isModLoaded first and only then touches MasterCompat (which does reference MastersEntity). DMZ is
// mandatory for SU, but the guard stays so the wand degrades cleanly if that ever changes.
public final class DeletionWandMasterGuard
{
    private DeletionWandMasterGuard() {}

    private static final boolean DMZ_LOADED = ModList.get().isLoaded("dragonminez");

    public record MasterInfo(boolean isMaster, boolean respawning, String label)
    {
        static final MasterInfo NONE = new MasterInfo(false, false, null);
    }

    // NONE when DMZ absent or not a master; else the label + whether DMZ respawns it
    public static MasterInfo inspect(Entity entity)
    {
        if (!DMZ_LOADED || entity == null)
            return MasterInfo.NONE;
        if (!MasterCompat.isMaster(entity))
            return MasterInfo.NONE;
        return new MasterInfo(true, MasterCompat.isRespawningMaster(entity), MasterCompat.masterLabel(entity));
    }
}
