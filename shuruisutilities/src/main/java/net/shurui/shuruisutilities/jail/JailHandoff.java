package net.shurui.shuruisutilities.jail;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * A cross-server placement intent for jailing, carried on a player's PERSISTED NBT.
 *
 * <h2>Why this exists</h2>
 * A jail lives in an open world hosted by one shard. An admin who jails a player from a DIFFERENT shard (the SMP,
 * say) cannot teleport them into a dimension this server does not host: teleporting into a locally created empty
 * copy would just get them evicted a second later by {@link net.shurui.shuruisutilities.shard.ShardDimensions}. So
 * the jail this player is bound for (by NAME) is written here BEFORE the transfer, travels inside the vault payload
 * with the rest of the player's Forge persistent data, and is read once on arrival to place the player in the jail.
 * The jail's coordinates are NOT carried: the shard that owns the jail dimension is the shard that defined the jail
 * (that is where {@code /setjail} was run), so it already has the {@link JailPoint} in its cache and looks it up by
 * name on arrival.
 *
 * <h2>Why it can never start a transfer loop</h2>
 * The record is CONSUMED on arrival (cleared whether or not the placement succeeds), it carries a timestamp and is
 * ignored once older than {@link #TTL_MILLIS}, and the arrival side NEVER re-forwards on it: if the server it lands
 * on does not host the jail dimension, the intent is dropped and the player is left where they loaded with a log,
 * exactly the eviction-loop philosophy {@link net.shurui.shuruisutilities.world.space.SpaceHandoff} follows. Re-issuing a
 * fresh handoff (the jailbreak guard for a player who hopped servers to escape) happens only at LOGIN, never per
 * tick, and only ever names a DIFFERENT shard than this one, so it too cannot bounce a player in a loop.
 *
 * <p>Stored in the PlayerPersisted sub-tag (like {@link JailData}) so Forge copies it onto the respawn clone and it
 * survives the relog that a seamless transfer really is under the hood.
 */
public final class JailHandoff
{
    private JailHandoff()
    {
    }

    private static final String TAG = "su_jail_handoff";

    // Ignore an intent older than this. A vault hop lands within milliseconds, so a minute is generous headroom and
    // still guarantees a record left behind by a hop that never completed can never re-jail the player on some
    // unrelated later login.
    private static final long TTL_MILLIS = 60_000L;

    // the PlayerPersisted compound, created and re-attached if absent (same idiom as JailData).
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

    /** Record which jail a transferring player should be placed in on the far side (does not move them). */
    public static void mark(Player player, String jailName)
    {
        CompoundTag t = new CompoundTag();
        t.putString("jail", jailName);
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

    public static String jailName(Player player)
    {
        return persisted(player).getCompound(TAG).getString("jail");
    }
}
