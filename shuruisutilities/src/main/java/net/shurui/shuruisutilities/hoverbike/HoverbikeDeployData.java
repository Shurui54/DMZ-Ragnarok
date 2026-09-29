package net.shurui.shuruisutilities.hoverbike;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

// per-player record of a deployed curios bike, in PlayerPersisted NBT (su_hoverbike) so it survives relog +
// death (Forge copies that sub-tag onto the respawn clone). holds bike UUID + variant for recall.
public final class HoverbikeDeployData
{
    private HoverbikeDeployData() {}

    private static final String TAG = "su_hoverbike";

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
        return persisted(player).getCompound(TAG).hasUUID("bike");
    }

    public static UUID deployedUUID(Player player)
    {
        CompoundTag h = persisted(player).getCompound(TAG);
        return h.hasUUID("bike") ? h.getUUID("bike") : null;
    }

    public static int deployedVariant(Player player)
    {
        return persisted(player).getCompound(TAG).getInt("variant");
    }

    public static void set(Player player, UUID bike, int variant)
    {
        CompoundTag h = new CompoundTag();
        h.putUUID("bike", bike);
        h.putInt("variant", variant);
        // Stamp the server that wrote this, so a copy carried to another shard in the vault can be told
        // apart from a record about a vehicle in an unloaded chunk here. See VehicleDeployOrigin.
        VehicleDeployOrigin.stamp(h);
        persisted(player).put(TAG, h);
    }

    /**
     * True when this record was written on a DIFFERENT server, so the vehicle it names cannot be here at all.
     * False for an unstamped record (written before the stamp existed, or off a shard network).
     */
    public static boolean deployedElsewhere(Player player)
    {
        return VehicleDeployOrigin.foreign(persisted(player).getCompound(TAG));
    }

    public static void clear(Player player)
    {
        persisted(player).remove(TAG);
    }
}
