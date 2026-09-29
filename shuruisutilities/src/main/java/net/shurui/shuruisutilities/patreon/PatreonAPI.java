package net.shurui.shuruisutilities.patreon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.entity.player.Player;

/**
 * Stable, server-side reward hook other Shurui's Utilities features gate on. All reads reflect the grace-aware
 * effective tier (see {@link PatreonManager#effectiveTier}): during a backend outage a supporter keeps their tier
 * until the grace window elapses. The tier ladder itself is fixed in {@link PatreonTiers}, identical on every server.
 *
 * <p>Typical use: {@code if (PatreonAPI.hasTierAtLeast(player, "gold")) { ... }} or
 * {@code if (PatreonAPI.hasReward(player, "perk.monthly_crate")) { ... }}.
 */
public final class PatreonAPI
{
    private PatreonAPI() {}

    //
    // These are the stable STRING contract other features gate on. Which TIER grants each one is NOT decided here:
    // these keys hang off fixed tiers in PatreonTiers, so which tier grants what is the same everywhere and the
    // data-driven and never hardcoded in Java. A key only "means" something because a feature below reads it.

    /** Reward that lets a supporter join past the server's max-players cap (see the PlayerList login mixin). */
    public static final String REWARD_PLAYER_LIMIT_BYPASS = "perk.player_limit_bypass";

    /** Reward that unlocks the single-form cosmetic menu in the Cosmetics screen. */
    public static final String REWARD_FORM_COSMETIC = "cosmetic.form";

    /**
     * Prefix of a wardrobe reward: {@code wardrobe.<catalogId>} entitles the holder to wear that wardrobe cosmetic
     * on every server, keyless included (see {@link PatreonWardrobe}). Only an explicitly listed key counts; the
     * owners' {@code "*"} grant does not expand to the catalogue.
     */
    public static final String REWARD_WARDROBE_PREFIX = "wardrobe.";

    /** Whether the linking feature is configured at all. When false, every query below returns "no tier". */
    public static boolean isConfigured()
    {
        return PatreonManager.isConfigured();
    }

    /** The player's current effective tier id, or "" when they have none (or the feature is off). */
    public static String getTier(UUID uuid)
    {
        return PatreonManager.effectiveTier(uuid);
    }

    /** The player's current effective tier id, or "" when they have none (or the feature is off). */
    public static String getTier(Player player)
    {
        return player == null ? "" : getTier(player.getUUID());
    }

    /** True when the player currently has any entitled tier. */
    public static boolean hasAnyTier(Player player)
    {
        return player != null && !getTier(player).isEmpty();
    }

    /** The numeric rank of the player's effective tier, or 0 when they have none. Higher is a bigger supporter. */
    public static int getTierRank(Player player)
    {
        return PatreonTiers.rankOf(getTier(player));
    }

    /**
     * True when the player's effective tier is at least the given tier id (compared by configured rank). Returns
     * false if {@code tierId} is not on the fixed ladder, so an unknown gate never accidentally admits everyone.
     */
    public static boolean hasTierAtLeast(Player player, String tierId)
    {
        if (player == null || tierId == null || tierId.isBlank())
            return false;
        if (!PatreonTiers.isDefined(tierId))
            return false;
        return getTierRank(player) >= PatreonTiers.rankOf(tierId);
    }

    /** {@link #hasTierAtLeast(Player, String)} by UUID, reading the same grace-aware effective tier. */
    public static boolean hasTierAtLeast(UUID uuid, String tierId)
    {
        if (uuid == null || tierId == null || tierId.isBlank())
            return false;
        if (!PatreonTiers.isDefined(tierId))
            return false;
        return PatreonTiers.rankOf(getTier(uuid)) >= PatreonTiers.rankOf(tierId);
    }

    /**
     * The reward keys the player currently holds: the union of their permanent grants (see {@link PatreonGrants}) and
     * the rewards attached to their effective Patreon tier. Empty when they hold neither.
     */
    public static List<String> getRewards(Player player)
    {
        return player == null ? List.of() : getRewards(player.getUUID());
    }

    /**
     * The reward keys the UUID currently holds: the union of their permanent grants (see {@link PatreonGrants}) and the
     * rewards attached to their effective Patreon tier. Empty when they hold neither.
     */
    public static List<String> getRewards(UUID uuid)
    {
        Set<String> out = new LinkedHashSet<>(PatreonManager.permanentRewards(uuid));
        out.addAll(PatreonTiers.rewardsOf(getTier(uuid)));
        return List.copyOf(out);
    }

    /** True when the player holds the given reward, whether from a permanent grant or their effective tier. */
    public static boolean hasReward(Player player, String rewardKey)
    {
        if (player == null || rewardKey == null || rewardKey.isBlank())
            return false;
        return hasReward(player.getUUID(), rewardKey);
    }

    /**
     * True when the effective tier of this UUID grants the given reward key. UUID-keyed so login-time hooks (before
     * the player entity exists) can query it. Reads the grace-aware cache, so during a backend outage a recently
     * confirmed supporter still qualifies, while an unknown/never-confirmed UUID never does.
     */
    public static boolean hasReward(UUID uuid, String rewardKey)
    {
        if (uuid == null || rewardKey == null || rewardKey.isBlank())
            return false;
        // Permanent grants stand regardless of Patreon status, so they are checked first and never depend on the
        // backend being configured or reachable.
        if (PatreonManager.hasPermanentReward(uuid, rewardKey))
            return true;
        return PatreonTiers.rewardsOf(getTier(uuid)).contains(rewardKey);
    }

    /** A human-facing display name for a tier id (its configured displayName, or the raw id). */
    public static String displayName(String tierId)
    {
        return PatreonTiers.displayNameOf(tierId);
    }
}
