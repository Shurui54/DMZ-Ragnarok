package net.shurui.shuruisutilities.wish;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;

/**
 * Durable per-player bonus to the maximum ki release ceiling, earned from a Super dragon ball wish (see
 * {@link ReleaseBoostCommand}). Every wish adds {@link #BOOST_PER_WISH} to the stored total.
 *
 * <p>It lives in the SU permission-property store, the same per-UUID flatfile used by
 * {@link net.shurui.shuruisutilities.ritual.WishRitualStore}, and deliberately NOT in DMZ's own {@code StatsData}
 * capability. Two reasons that matter here: the bonus must survive a DMZ character reset (which wipes StatsData and
 * would otherwise silently refund a wish the player paid seven dragon balls for), and it must be readable for an
 * offline player without resolving the capability.
 *
 * <p>DMZ has no stored release ceiling to raise: it recomputes {@code 50 + potentialunlock * 5} at three sites every
 * time it needs the number, and the {@code potentialunlock} skill level is clamped to a config maximum, so the skill
 * cannot be pushed past it. The three release mixins read this store and add the bonus on top of that formula.
 *
 * <p>Values are plain decimal integers; this class owns only the state. It clamps reads at 0 and never stores a
 * negative, so a corrupt or hand-edited property degrades to "no bonus" rather than a subtraction from the ceiling.
 */
public final class ReleaseBoostStore
{
    private ReleaseBoostStore() {}

    /** How much one wish adds to the stored release bonus. */
    public static final int BOOST_PER_WISH = 25;

    /** The property node the bonus is stored under, as a decimal integer. */
    private static final String NODE = "su.wish.releaseboost";

    /**
     * Online players' bonuses, so the hot path does not hit the property store.
     *
     * <p>This is not premature: {@code chargePowerRelease} runs EVERY TICK for every player holding the ki charge,
     * and the uncached read is a property lookup plus a {@code UserIdent} resolve plus an {@code Integer.parseInt},
     * twenty times a second per charging player. The value only changes when a wish is granted, which writes through
     * here, so the cache cannot go stale on its own.
     *
     * <p>Entries are dropped on logout by {@link ReleaseBoostEvents}, so this does not grow without bound.
     */
    private static final Map<UUID, Integer> CACHE = new ConcurrentHashMap<>();

    /**
     * The player's stored release bonus. 0 on any error or for an absent property, so a missing store reads as no
     * bonus rather than throwing into the render or tick path that calls it.
     */
    public static int bonus(ServerPlayer player)
    {
        if (player == null)
            return 0;
        Integer cached = CACHE.get(player.getUUID());
        if (cached != null)
            return cached;
        int value = read(player);
        CACHE.put(player.getUUID(), value);
        return value;
    }

    private static int read(ServerPlayer player)
    {
        try
        {
            return parse(APIRegistry.perms.getUserPermissionProperty(UserIdent.get(player), NODE));
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /** Forget a player's cached bonus. Called on logout so the map tracks online players only. */
    public static void forget(ServerPlayer player)
    {
        if (player != null)
            CACHE.remove(player.getUUID());
    }

    /**
     * One entry point the mixins can call with any {@link Player}. Returns 0 for a client-side or otherwise
     * non-server player, because the store only exists server side; the client learns its own bonus over
     * {@link PacketReleaseBoost} and reads it from {@link ReleaseBoostClient} instead.
     */
    public static int bonusFor(Player player)
    {
        if (player instanceof ServerPlayer serverPlayer)
            return bonus(serverPlayer);
        return 0;
    }

    /**
     * Add {@link #BOOST_PER_WISH} to the player's stored bonus and return the new total. Floors at 0 on read, so a
     * fresh player starts from a clean 25.
     */
    public static int grant(ServerPlayer player)
    {
        if (player == null)
            return 0;
        int next = bonus(player) + BOOST_PER_WISH;
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(UserIdent.get(player), NODE, Integer.toString(next));
        }
        catch (Throwable ignored)
        {
        }
        // Written through rather than invalidated: the mixins read this on the tick path, and a miss there would
        // send them back to the property store for a value we already hold.
        CACHE.put(player.getUUID(), next);
        return next;
    }

    private static int parse(String raw)
    {
        if (raw == null || raw.isEmpty())
            return 0;
        try
        {
            return Math.max(0, Integer.parseInt(raw.trim()));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
