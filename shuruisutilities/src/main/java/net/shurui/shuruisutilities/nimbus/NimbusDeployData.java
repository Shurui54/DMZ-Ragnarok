package net.shurui.shuruisutilities.nimbus;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

// per-player record of a deployed nimbus, in PlayerPersisted NBT (su_nimbus) so it survives relog + death (Forge
// copies that sub-tag onto the respawn clone). holds only the nimbus UUID for recall: the chip has no variant, and
// the spawned entity (flying vs black) is scanned by UUID at recall so which class it is does not need storing.
// deliberately SEPARATE from HoverbikeDeployData and PodDeployData even though all three use the one shared
// "hoverbike" slot, so the bike/pod code stays untouched. the shared slot guarantees at most one of the three
// records is ever set at once.
public final class NimbusDeployData
{
    private NimbusDeployData() {}

    private static final String TAG = "su_nimbus";

    // the PlayerPersisted compound, created + re-attached if missing
    private static CompoundTag persisted(Player player)
    {
        CompoundTag data = player.getPersistentData();
        CompoundTag pt = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG))
        {
            data.put(Player.PERSISTED_NBT_TAG, pt);
        }
        return pt;
    }

    public static boolean hasDeployed(Player player)
    {
        return persisted(player).getCompound(TAG).hasUUID("nimbus");
    }

    public static UUID deployedUUID(Player player)
    {
        CompoundTag h = persisted(player).getCompound(TAG);
        return h.hasUUID("nimbus") ? h.getUUID("nimbus") : null;
    }

    public static void set(Player player, UUID nimbus)
    {
        CompoundTag h = new CompoundTag();
        h.putUUID("nimbus", nimbus);
        // Stamp the server that wrote this, so a copy carried to another shard in the vault can be told
        // apart from a record about a vehicle in an unloaded chunk here. See VehicleDeployOrigin.
        net.shurui.shuruisutilities.hoverbike.VehicleDeployOrigin.stamp(h);
        persisted(player).put(TAG, h);
    }

    /**
     * True when this record was written on a DIFFERENT server, so the vehicle it names cannot be here at all.
     * False for an unstamped record (written before the stamp existed, or off a shard network).
     */
    public static boolean deployedElsewhere(Player player)
    {
        return net.shurui.shuruisutilities.hoverbike.VehicleDeployOrigin.foreign(persisted(player).getCompound(TAG));
    }

    public static void clear(Player player)
    {
        persisted(player).remove(TAG);
    }
}
