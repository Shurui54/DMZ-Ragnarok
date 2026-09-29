package net.shurui.shuruisutilities.timemachine;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Per-player record of a deployed time machine, in PlayerPersisted NBT ({@code su_time_machine}) so it survives
 * relog + death (Forge copies that sub-tag onto the respawn clone) AND rides the cross-shard vault payload with the
 * rest of the player's Forge persistent data, exactly like {@link net.shurui.shuruisutilities.spacepod.PodDeployData}.
 *
 * <p>Single-entry per player and owned solely by that player's own session, so plain last-write-wins is correct
 * here: there is no second writer to race, so no tombstone engine is needed (contrast the multi-entry SavedData
 * stores). It holds only the machine UUID for recall.
 *
 * <p>Deliberately SEPARATE from the hoverbike / pod / nimbus records even though all four share the one "hoverbike"
 * curios slot, so each vehicle's code stays independent. The shared slot guarantees at most one of the four records
 * is ever set at once.
 */
public final class TimeMachineDeployData
{
    private TimeMachineDeployData() {}

    private static final String TAG = "su_time_machine";

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
        return persisted(player).getCompound(TAG).hasUUID("machine");
    }

    public static UUID deployedUUID(Player player)
    {
        CompoundTag h = persisted(player).getCompound(TAG);
        return h.hasUUID("machine") ? h.getUUID("machine") : null;
    }

    public static void set(Player player, UUID machine)
    {
        CompoundTag h = new CompoundTag();
        h.putUUID("machine", machine);
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
