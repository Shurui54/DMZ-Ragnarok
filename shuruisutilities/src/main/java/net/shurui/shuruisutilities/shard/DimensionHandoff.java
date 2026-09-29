package net.shurui.shuruisutilities.shard;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A cross-server placement intent for a dimension change, carried on a player's PERSISTED NBT.
 *
 * <p>Every server loads every registered dimension, so a dimension one shard owns exists, empty, on the others.
 * Rather than drop a player into that empty copy or refuse, we hand them to the shard that owns it and finish
 * placement there (see {@link ShardDimensions#onTravel}). The destination is written here BEFORE the handoff,
 * travels inside the vault payload with the rest of the Forge persistent data ({@code ShardPayload} copies the
 * whole persistent compound), and is read once on arrival.
 *
 * <p>Two shapes. Instant Transmission knows dimension AND coordinates, so it uses the coordinate carrying
 * {@link #mark(Player, ResourceKey, double, double, double, float, float)} and arrival re-snaps against the real
 * world. A generic {@code EntityTravelToDimensionEvent} exposes only the target dimension, so it uses the
 * positionless {@link #mark(Player, ResourceKey)}; arrival then places the player at the DESTINATION's own
 * {@code getSharedSpawnPos()}. The origin must NOT answer "where in that world", its copy is empty.
 *
 * <p>Cannot start a transfer loop: the record is CONSUMED on arrival (success or not), carries a timestamp and is
 * ignored past {@link #TTL_MILLIS}, and {@code DimensionHandoffEvents} NEVER re-forwards on it, matching the
 * eviction-loop philosophy of {@link net.shurui.shuruisutilities.world.space.SpaceHandoff} and
 * {@link net.shurui.shuruisutilities.jail.JailHandoff}.
 *
 * <p>Lives in {@code shard} because a dimension hop has no owning gameplay module, unlike SpaceHandoff and
 * JailHandoff; it is pure shard-transfer machinery. Stored in the PlayerPersisted sub-tag so Forge copies it onto
 * the respawn clone and it survives the relog a seamless transfer really is.
 */
public final class DimensionHandoff
{
    private DimensionHandoff()
    {
    }

    private static final String TAG = "su_dim_handoff";

    // ignore an intent older than this. A vault hop lands within ms, so a minute is generous headroom and still
    // stops a leftover record from a hop that never completed teleporting the player on some later login.
    private static final long TTL_MILLIS = 60_000L;

    // the PlayerPersisted compound, created and re-attached if absent (same idiom as SpaceHandoff / JailHandoff)
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

    /** Record a destination WITH an explicit position (does not move them; the caller does). Used by Instant Transmission. */
    public static void mark(Player player, ResourceKey<Level> dimension, double x, double y, double z,
            float yaw, float pitch)
    {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dimension.location().toString());
        t.putBoolean("haspos", true);
        t.putDouble("x", x);
        t.putDouble("y", y);
        t.putDouble("z", z);
        t.putFloat("yaw", yaw);
        t.putFloat("pitch", pitch);
        t.putLong("at", System.currentTimeMillis());
        persisted(player).put(TAG, t);
    }

    /**
     * Record a destination dimension WITHOUT a position (does not move them; the caller does). A generic
     * {@code EntityTravelToDimensionEvent} exposes only the dimension, so where in that world the player belongs is
     * answered on ARRIVAL by the owning shard via {@code getSharedSpawnPos()}. See {@code DimensionHandoffEvents}.
     */
    public static void mark(Player player, ResourceKey<Level> dimension)
    {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dimension.location().toString());
        t.putBoolean("haspos", false);
        t.putLong("at", System.currentTimeMillis());
        persisted(player).put(TAG, t);
    }

    public static boolean isPending(Player player)
    {
        return persisted(player).contains(TAG);
    }

    public static void clear(Player player)
    {
        persisted(player).remove(TAG);
    }

    /** True when the record is present but past its TTL, so the caller drops it rather than acting on it. */
    public static boolean expired(Player player)
    {
        if (!isPending(player))
        {
            return false;
        }
        long at = persisted(player).getCompound(TAG).getLong("at");
        return at <= 0L || System.currentTimeMillis() - at > TTL_MILLIS;
    }

    /** True when the record carries explicit coordinates; false when arrival must fall back to the shared spawn. */
    public static boolean hasPosition(Player player)
    {
        return persisted(player).getCompound(TAG).getBoolean("haspos");
    }

    /** The recorded destination dimension, or null when the stored id does not parse. */
    public static ResourceKey<Level> dimension(Player player)
    {
        ResourceLocation loc = ResourceLocation.tryParse(persisted(player).getCompound(TAG).getString("dim"));
        return loc == null ? null : ResourceKey.create(Registries.DIMENSION, loc);
    }

    public static double x(Player player)
    {
        return persisted(player).getCompound(TAG).getDouble("x");
    }

    public static double y(Player player)
    {
        return persisted(player).getCompound(TAG).getDouble("y");
    }

    public static double z(Player player)
    {
        return persisted(player).getCompound(TAG).getDouble("z");
    }

    public static float yaw(Player player)
    {
        return persisted(player).getCompound(TAG).getFloat("yaw");
    }

    public static float pitch(Player player)
    {
        return persisted(player).getCompound(TAG).getFloat("pitch");
    }
}
