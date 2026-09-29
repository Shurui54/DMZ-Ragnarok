package net.shurui.shuruisutilities.world.space;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A cross-server placement intent carried on a player's PERSISTED NBT.
 *
 * <h2>Why this exists</h2>
 * The space dimension ({@link SpaceDimension}) is hosted on ONE shard, the SMP. A player who flies up into space
 * from an open world therefore has to be handed to that shard, and a plain server transfer lands them at the SMP's
 * ordinary arrival point, not in space. So the intended destination (dimension plus position) is written here
 * BEFORE the transfer, travels inside the vault payload with the rest of the player's Forge persistent data (see
 * {@code ShardPayload}, which copies the whole persistent compound wholesale), and is read once on arrival to place
 * the player where they were actually going. The reverse trip (descending out of space back to an open world the
 * SMP does not host) uses the exact same record in the other direction.
 *
 * <h2>Why it can never start a transfer loop</h2>
 * The record is CONSUMED on arrival (cleared whether or not the placement succeeds), it carries a timestamp and is
 * ignored once older than {@link #TTL_MILLIS}, and the arrival side NEVER re-forwards on it: if the server it lands
 * on does not host the target dimension, the intent is dropped and the player is left where they loaded with a log,
 * exactly the philosophy the eviction-loop fix in {@code ShardDimensions} follows. The one thing that would turn a
 * transfer into a loop, blindly forwarding a stale intent onward, is deliberately not done anywhere.
 *
 * <p>Stored in the PlayerPersisted sub-tag (like {@link SpaceTravelData}) so Forge copies it onto the respawn clone
 * and it survives the relog that a seamless transfer really is under the hood.
 */
public final class SpaceHandoff
{
    private SpaceHandoff()
    {
    }

    private static final String TAG = "su_space_handoff";

    // Ignore an intent older than this. A vault hop lands within milliseconds, so a minute is generous headroom and
    // still guarantees a record left behind by a hop that never completed can never teleport the player on some
    // unrelated later login.
    private static final long TTL_MILLIS = 60_000L;

    // the PlayerPersisted compound, created and re-attached if absent (same idiom as SpaceTravelData).
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

    /** Record where a transferring player should be placed on the far side (does not move them; the caller does). */
    public static void mark(Player player, ResourceKey<Level> dimension, double x, double y, double z,
            float yaw, float pitch, boolean pod, boolean timeMachine)
    {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dimension.location().toString());
        t.putDouble("x", x);
        t.putDouble("y", y);
        t.putDouble("z", z);
        t.putFloat("yaw", yaw);
        t.putFloat("pitch", pitch);
        t.putBoolean("pod", pod);
        t.putBoolean("timeMachine", timeMachine);
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

    /**
     * Attach the player's launch origin (the whole {@code su_space_origin} record) to a pending ENTER-space handoff, so
     * a cross-shard space entry carries it explicitly and can re-establish it on arrival even if the separate origin tag
     * were ever lost in the vault hop. A no-op when there is no pending handoff or no origin to attach.
     */
    public static void attachOrigin(Player player, CompoundTag origin)
    {
        if (origin == null || !isPending(player))
        {
            return;
        }
        persisted(player).getCompound(TAG).put("origin", origin.copy());
    }

    /** The launch origin carried with an ENTER-space handoff, or null when none was attached. */
    public static CompoundTag origin(Player player)
    {
        CompoundTag t = persisted(player).getCompound(TAG);
        return t.contains("origin") ? t.getCompound("origin").copy() : null;
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

    /** Whether a fresh space pod should be rebuilt under the player on arrival (they were riding one on departure). */
    public static boolean pod(Player player)
    {
        return persisted(player).getCompound(TAG).getBoolean("pod");
    }

    /**
     * Mark the pod a pending handoff carries as DMZ's own saiyan ship (spawned from its ship item) rather than an SU
     * pod deployed from the chip, so the far side rebuilds it as a DMZ ship and any hand-back returns DMZ's ship item,
     * never a chip. A no-op when no handoff is pending. Additive optional key: a record without it (written before it
     * existed) reads as an SU pod, exactly as before.
     */
    public static void markPodShip(Player player)
    {
        if (!isPending(player))
        {
            return;
        }
        persisted(player).getCompound(TAG).putBoolean("podShip", true);
    }

    /** True when the carried pod is a DMZ saiyan ship (see {@link #markPodShip}); false for an SU pod or an old record. */
    public static boolean podShip(Player player)
    {
        return persisted(player).getCompound(TAG).getBoolean("podShip");
    }

    /** Whether a fresh time machine should be rebuilt under the player on arrival (they were riding one on departure). */
    public static boolean timeMachine(Player player)
    {
        return persisted(player).getCompound(TAG).getBoolean("timeMachine");
    }
}
