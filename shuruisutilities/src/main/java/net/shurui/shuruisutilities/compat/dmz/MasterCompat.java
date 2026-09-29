package net.shurui.shuruisutilities.compat.dmz;

import java.util.Set;

import com.dragonminez.common.init.entities.MastersEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

// DMZ master-NPC compat. references MastersEntity directly, so only classload it behind an isModLoaded guard
// (DeletionWandMasterGuard). masters reject normal damage but discard() bypasses that. DMZ re-places only the
// four manual/override masters on restart/reload; deleted structure masters stay gone. reports whether a hit
// entity is a master and, if so, whether it's one of the respawning four (so we can warn the admin).
final class MasterCompat
{
    private MasterCompat() {}

    // the four manual/override masters DMZ re-places on restart/reload. Baba is master_uranai; master_baba is a
    // defensive alias in case DMZ's config ever keys her under the short id.
    private static final Set<String> RESPAWNING_MASTERS = Set.of(
            "master_kaiosama",
            "master_enma",
            "master_uranai",
            "master_baba",
            "master_toribot");

    static boolean isMaster(Entity entity)
    {
        return entity instanceof MastersEntity;
    }

    // label for a master ("kaiosama", ...), falling back to registry path
    static String masterLabel(Entity entity)
    {
        if (entity instanceof MastersEntity master)
        {
            String name = master.getMasterName();
            if (name != null && !name.isEmpty())
                return name;
        }
        return registryPath(entity);
    }

    static boolean isRespawningMaster(Entity entity)
    {
        if (!(entity instanceof MastersEntity))
            return false;
        return RESPAWNING_MASTERS.contains(registryPath(entity));
    }

    private static String registryPath(Entity entity)
    {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id == null ? entity.getType().getDescriptionId() : id.getPath();
    }
}
