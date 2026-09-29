package net.shurui.shuruisutilities.hoverbike;

import net.minecraft.nbt.CompoundTag;

import net.shurui.shuruisutilities.shard.ShardConfig;

/**
 * Which server a vehicle deploy record was written on.
 *
 * <h2>Why a record needs to say that</h2>
 * The four deploy records (hoverbike, space pod, nimbus, time machine) live in {@code Player.PERSISTED_NBT_TAG},
 * which the cross-shard vault payload carries wholesale. The vehicle is an ENTITY and stays in the world it was
 * spawned in. So a player who changes servers with a vehicle out arrives holding a record that names something not
 * on this server, and every check that could clear it is a "not found" that is indistinguishable from an unloaded
 * chunk: {@code VehicleRecallWatch} deliberately stands aside without a sighting from this session, and nothing else
 * clears it. The record sticks for good, which is ticket #767.
 *
 * <p>Stamping the writer's server id into the record turns that unanswerable question into a plain one: a record
 * whose stamp names a DIFFERENT server than the one reading it cannot be about anything here, whatever the chunk
 * loader says. A record with no stamp at all (written before this existed, or on a server that is not on a shard
 * network) is left alone, so nothing that works today changes.
 *
 * <p>Clearing such a record can never cost an item: all four vehicles keep their curios item in the slot for the
 * whole time they are deployed, precisely so losing the entity cannot lose the item. It costs an orphan entity on
 * the origin, which is already what happens today and is the lesser of the two.
 */
public final class VehicleDeployOrigin
{
    private VehicleDeployOrigin() {}

    private static final String KEY = "server";

    /** Stamp the writing server onto a freshly built deploy record. A no-op off a shard network. */
    public static void stamp(CompoundTag record)
    {
        String self = ShardConfig.selfId();
        if (record != null && self != null && !self.isBlank())
        {
            record.putString(KEY, self);
        }
    }

    /**
     * True only when the record positively names a server that is NOT this one. An unstamped record, or a server
     * with no id of its own, answers false: unknown is never treated as foreign.
     */
    public static boolean foreign(CompoundTag record)
    {
        if (record == null || !record.contains(KEY))
        {
            return false;
        }
        String self = ShardConfig.selfId();
        if (self == null || self.isBlank())
        {
            return false;
        }
        String wrote = record.getString(KEY);
        return !wrote.isBlank() && !wrote.equals(self);
    }
}
