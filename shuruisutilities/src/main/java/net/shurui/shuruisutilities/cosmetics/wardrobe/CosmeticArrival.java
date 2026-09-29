package net.shurui.shuruisutilities.cosmetics.wardrobe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * A cross-server ARRIVE-ANIMATION intent, carried on a player's PERSISTED NBT, modelled line for line on
 * {@code DimensionHandoff}.
 *
 * <p>The problem: a shard hop is a disconnect on the origin and a login on the destination, so the destination
 * cannot tell "arrived from a teleport" from "logged in fresh" without help. The origin writes this marker BEFORE
 * the handoff; it travels inside the vault payload with the rest of the Forge persistent data (the vault copies
 * the whole persistent compound) and is read once on the destination's login, at LOW priority, after the vault
 * has been applied.
 *
 * <p>Carries only the RESOLVED arrive-animation id (a diagnostic fallback) and a timestamp, never a definition:
 * the equipped set already travels in the vault, so the destination reads its own wardrobe first and uses this id
 * only when that read is blank. Consumed on every arrival path (see the login handler's {@code finally}) and
 * ignored past {@link #TTL_MILLIS}, so a hop that never completed self-heals into an ordinary JOIN.
 *
 * <p>Never re-forwards and cannot start a loop, exactly like {@code DimensionHandoff}.
 */
public final class CosmeticArrival
{
    private CosmeticArrival()
    {
    }

    private static final String TAG = "su_cos_arrival";

    /** Ignore an intent older than this. A vault hop lands within ms, so a minute is generous and still safe. */
    private static final long TTL_MILLIS = 60_000L;

    private static CompoundTag persisted(Player player)
    {
        CompoundTag data = player.getPersistentData();
        CompoundTag pt = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG))
            data.put(Player.PERSISTED_NBT_TAG, pt);
        return pt;
    }

    /** Record an arrive-animation intent (does not move the player; the hop does). Written BEFORE the handoff. */
    public static void mark(Player player, String arriveCatalogId)
    {
        if (player == null)
            return;
        CompoundTag t = new CompoundTag();
        t.putString("anim", arriveCatalogId == null ? "" : arriveCatalogId);
        t.putLong("at", System.currentTimeMillis());
        persisted(player).put(TAG, t);
    }

    public static boolean isPending(Player player)
    {
        return player != null && persisted(player).contains(TAG);
    }

    /**
     * Whether this login is a recent HOP arrival: the marker is present and within the TTL. The destination uses
     * this to SUPPRESS the JOIN animation on a hop (a hop is a teleport, not a fresh login), whether or not an
     * arrive animation is equipped. An expired marker is not a hop and lets JOIN fire, which self-heals a hop that
     * never completed.
     */
    public static boolean isFreshHop(Player player)
    {
        if (player == null)
            return false;
        CompoundTag pt = persisted(player);
        if (!pt.contains(TAG))
            return false;
        long at = pt.getCompound(TAG).getLong("at");
        return System.currentTimeMillis() - at <= TTL_MILLIS;
    }

    /** The carried arrive id, or blank when absent or expired. Does NOT consume; callers clear in a finally. */
    public static String read(Player player)
    {
        if (player == null)
            return "";
        CompoundTag pt = persisted(player);
        if (!pt.contains(TAG))
            return "";
        CompoundTag t = pt.getCompound(TAG);
        long at = t.getLong("at");
        if (System.currentTimeMillis() - at > TTL_MILLIS)
            return "";
        return t.getString("anim");
    }

    /** Remove the marker. MUST be called on every arrival path so a leftover cannot re-fire on a later login. */
    public static void clear(Player player)
    {
        if (player != null)
            persisted(player).remove(TAG);
    }
}
