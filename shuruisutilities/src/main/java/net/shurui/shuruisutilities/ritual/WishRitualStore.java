package net.shurui.shuruisutilities.ritual;

import java.util.Locale;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;

/**
 * Durable per-player state for the dragon-ball wish rituals. Two counters (one per DMZ ball set, "earth" and "namek")
 * count how many wishes each player has taken, and a small set of one-shot entitlement flags record which ritual
 * rewards a player has already earned. All of it lives in the SU permission-property store, the same per-UUID flatfile
 * behind {@link net.shurui.shuruisutilities.corrupted.RaceUnlocks}, so it survives a DMZ character reset (which would
 * wipe DMZ's own {@code StatsData} capability) and can be read for an offline player.
 *
 * <p>Values are plain strings; counts are decimal integers, flags are {@code "1"} / absent. This class owns only the
 * state; {@link WishRitualManager} decides when to read and change it.
 */
public final class WishRitualStore
{
    private WishRitualStore() {}

    /** The threshold of wishes on a set after which the ritual offer appears (on the next, i.e. the 11th, wish). */
    public static final int WISH_THRESHOLD = 10;

    private static final String COUNT_PREFIX = "su.wishritual.";      // + <set> + ".count"
    private static final String SSJ5_GRANTED = "su.wishritual.ssj5.granted";
    private static final String PRIMAL_NAMEKIAN_GRANTED = "su.wishritual.primalnamekian.granted";
    private static final String NAMEKIAN_GOD_GRANTED = "su.wishritual.namekiangod.granted";
    /**
     * Durable record that this player has wished on the Super Dragon Balls (DMZ ball set "super"). Kept alongside the
     * other ritual entitlements so the fact survives a DMZ character reset and can be read for an offline player, and
     * so "who has earned the super kit" is a queryable flag rather than something only the permission store knows. This
     * is the bookkeeping half; the KIT itself is opened by a separate permission node that is a function of this flag
     * (see {@code WishRitualManager.SUPER_KIT_PERMISSION}). Tracked apart from that node on purpose, the same way
     * {@link #NAMEKIAN_GOD_GRANTED} is tracked apart from the form it currently stands in for.
     */
    public static final String SUPER_BALL_WISH_GRANTED = "su.wishritual.super.granted";
    /** SSG purchase entitlement, earned by taking part in the Super Saiyan God charge ritual. */
    public static final String SSG_PURCHASE = "su.ssg.purchase";
    /**
     * Knowledge of Super Saiyan God, wished for from Shenron. This is what makes the charge ritual possible at
     * all: without it the five chargers can hold a perfect ring around a saiyan for as long as they like and
     * nothing happens. Kept apart from {@link #SSG_PURCHASE}, which is what the ritual then earns.
     */
    public static final String SSG_KNOWLEDGE = "su.ssg.knowledge";
    /**
     * Set while the {@code godforms} skill on this player's character is a TEMPORARY grant from the charge ritual
     * rather than something they own.
     *
     * <p>This flag exists because DMZ cannot answer the question itself. Buying Super Saiyan God (150000 TP) and being
     * granted it by the ritual leave identical state, {@code godforms} at level 1, and DMZ keeps no record of which
     * happened, so without this marker the ritual's cleanup had no way to tell a purchase from its own loan and took
     * both. Written by {@code RitualForms.beginTempSsg} only when the ritual is what raised the skill from 0, cleared
     * the moment the grant is revoked, and dropped at login when the skill is already back at 0 (a character reset
     * would otherwise leave it pointing at nothing).
     *
     * <p>Distinct from {@link #SSG_PURCHASE}, which is the durable RIGHT to buy the form and is never revoked.
     */
    public static final String SSG_TEMP_GRANT = "su.ssg.tempgrant";

    private static final String TRUE = "1";

    /** The DMZ ball-set id of the Super Dragon Balls. See {@link #isSuperSet}. */
    public static final String SUPER_SET_ID = "super";

    /** Normalise a raw ball-set id to "earth" / "namek", or null if it is neither. Case-insensitive. */
    public static String normalizeSet(String set)
    {
        if (set == null)
            return null;
        String lower = set.toLowerCase(Locale.ROOT);
        if (lower.equals("earth") || lower.equals("namek"))
            return lower;
        return null;
    }

    /**
     * Whether a raw ball-set id is the Super set, case-insensitively.
     *
     * <p>Kept DELIBERATELY apart from {@link #normalizeSet}: that method admits only the two sets that carry a wish
     * counter and a level-10k recreation ritual (earth, namek). Folding "super" into it would silently give the Super
     * set a counter it never uses and make the recreation ritual treat a recreated Super set as a
     * namekian reward, neither of which is wanted. The Super set has exactly one consequence (the kit entitlement), so
     * the manager branches on this ahead of {@code normalizeSet} and leaves the earth/namek path untouched.
     */
    public static boolean isSuperSet(String set)
    {
        return set != null && SUPER_SET_ID.equalsIgnoreCase(set);
    }

    private static String countNode(String normalizedSet)
    {
        return COUNT_PREFIX + normalizedSet + ".count";
    }

    /** The player's wish count on the given ball set ("earth" / "namek"). 0 for an unknown set or on any error. */
    public static int count(ServerPlayer player, String set)
    {
        String norm = normalizeSet(set);
        if (player == null || norm == null)
            return 0;
        try
        {
            String raw = APIRegistry.perms.getUserPermissionProperty(UserIdent.get(player), countNode(norm));
            return parse(raw);
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /** Overwrite the player's wish count on a set. No-op on an unknown set. */
    public static void setCount(ServerPlayer player, String set, int value)
    {
        String norm = normalizeSet(set);
        if (player == null || norm == null)
            return;
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(UserIdent.get(player), countNode(norm),
                    Integer.toString(Math.max(0, value)));
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Add {@code delta} wishes to the player's count on a set and return the new total (clamped at 0). */
    public static int add(ServerPlayer player, String set, int delta)
    {
        int next = Math.max(0, count(player, set) + delta);
        setCount(player, set, next);
        return next;
    }

    public static boolean hasSsj5(ServerPlayer player)
    {
        return flag(player, SSJ5_GRANTED);
    }

    public static void setSsj5(ServerPlayer player)
    {
        setFlag(player, SSJ5_GRANTED);
    }

    public static boolean hasPrimalNamekian(ServerPlayer player)
    {
        return flag(player, PRIMAL_NAMEKIAN_GRANTED);
    }

    public static void setPrimalNamekian(ServerPlayer player)
    {
        setFlag(player, PRIMAL_NAMEKIAN_GRANTED);
    }

    /**
     * Whether this player has earned Namekian God by restoring the dragon balls. Tracked separately from Primal
     * Namekian even though the placeholder currently grants that form, so the moment the real god form ships every
     * player who earned it can be found without guessing from the older flag.
     */
    public static boolean hasNamekianGod(ServerPlayer player)
    {
        return flag(player, NAMEKIAN_GOD_GRANTED);
    }

    public static void setNamekianGod(ServerPlayer player)
    {
        setFlag(player, NAMEKIAN_GOD_GRANTED);
    }

    /**
     * Whether this player has already wished on the Super Dragon Balls. Read by {@code WishRitualManager} as the
     * idempotency gate before it writes the kit permission, so a player who wishes again does not force a needless
     * rewrite of their whole permission block.
     */
    public static boolean hasSuperBallWish(ServerPlayer player)
    {
        return flag(player, SUPER_BALL_WISH_GRANTED);
    }

    public static void setSuperBallWish(ServerPlayer player)
    {
        setFlag(player, SUPER_BALL_WISH_GRANTED);
    }

    public static boolean hasSsgPurchase(ServerPlayer player)
    {
        return flag(player, SSG_PURCHASE);
    }

    public static void grantSsgPurchase(ServerPlayer player)
    {
        setFlag(player, SSG_PURCHASE);
    }

    /** True while this player's {@code godforms} skill is the ritual's loan rather than their own purchase. */
    public static boolean hasSsgTempGrant(ServerPlayer player)
    {
        return flag(player, SSG_TEMP_GRANT);
    }

    /** Record that the charge ritual, not a purchase, is what put the {@code godforms} skill on this player. */
    public static void markSsgTempGrant(ServerPlayer player)
    {
        setFlag(player, SSG_TEMP_GRANT);
    }

    /** Drop the temporary-grant marker, once the grant it describes is gone. */
    public static void clearSsgTempGrant(ServerPlayer player)
    {
        clearFlag(player, SSG_TEMP_GRANT);
    }

    public static boolean hasSsgKnowledge(ServerPlayer player)
    {
        return flag(player, SSG_KNOWLEDGE);
    }

    public static void grantSsgKnowledge(ServerPlayer player)
    {
        setFlag(player, SSG_KNOWLEDGE);
    }

    /** Offline-safe SSG-entitlement read used by the purchase gate before the character is resolved. */
    public static boolean hasSsgPurchase(UUID uuid)
    {
        if (uuid == null)
            return false;
        try
        {
            return TRUE.equals(APIRegistry.perms.getUserPermissionProperty(UserIdent.get(uuid), SSG_PURCHASE));
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static boolean flag(ServerPlayer player, String node)
    {
        if (player == null)
            return false;
        try
        {
            return TRUE.equals(APIRegistry.perms.getUserPermissionProperty(UserIdent.get(player), node));
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static void setFlag(ServerPlayer player, String node)
    {
        if (player == null)
            return;
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(UserIdent.get(player), node, TRUE);
        }
        catch (Throwable ignored)
        {
        }
    }

    // Remove the property outright rather than writing a "0": the store treats a flag as "1" or absent, and a
    // lingering falsy value would still take up a line in every read of the player's permission block.
    private static void clearFlag(ServerPlayer player, String node)
    {
        if (player == null)
            return;
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(UserIdent.get(player), node, null);
        }
        catch (Throwable ignored)
        {
        }
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
