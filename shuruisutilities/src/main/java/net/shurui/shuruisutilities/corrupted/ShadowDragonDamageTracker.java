package net.shurui.shuruisutilities.corrupted;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tracker of the damage each player deals to a specific live shadow dragon, and of which players died
 * during that dragon's fight. Keyed by the dragon entity's UUID so all seven can be tracked at once. Modelled on the
 * raid-bosses addon's {@code RaidDamageTracker}: nothing is written to NBT per hit. The {@code LivingDamageEvent}
 * handler calls {@link #record} on every qualifying hit; the {@code LivingDeathEvent} handler calls {@link
 * #recordDeath} whenever a player dies while a dragon is live. Both maps are read once, when the dragon dies, to
 * grant the matching shadow dragon sub-race to the top-damage player who did not die, after which {@link #clear}
 * frees the entry.
 *
 * <p>DELIBERATELY memory-only. A server restart loses partial damage attribution and the death set for a dragon that
 * was already being fought. That is a fairness edge, not a correctness one: the base race is granted on defiling (a
 * persisted property), the sub-race award simply does not fire for a fight whose accounting was lost to a restart,
 * and no player is wrongly rewarded. The live dragon set itself IS persisted in {@link ShadowDragonStorage}, so death
 * detection still works across a restart; only the pre-restart damage and death contributions are lost. The death set
 * is cleared on fight start ({@link #clearFight}, called at spawn) and on fight end ({@link #clear}, called at death),
 * so it can never leak between fights.
 */
public final class ShadowDragonDamageTracker
{
    private ShadowDragonDamageTracker() {}

    /** dragonEntityUUID -> (playerUUID -> total damage dealt). */
    private static final Map<UUID, Map<UUID, Double>> DAMAGE = new ConcurrentHashMap<>();

    /** dragonEntityUUID -> set of player UUIDs that died at any point during this dragon's fight. */
    private static final Map<UUID, Set<UUID>> DEATHS = new ConcurrentHashMap<>();

    /** Accumulate damage dealt by a player to a live dragon. Safe to call from the server thread. */
    public static void record(UUID dragonId, UUID player, double amount)
    {
        if (dragonId == null || player == null || amount <= 0)
            return;
        DAMAGE.computeIfAbsent(dragonId, k -> new ConcurrentHashMap<>()).merge(player, amount, Double::sum);
    }

    /**
     * Mark that a player died during this dragon's fight, disqualifying them from that dragon's sub-race award. Safe
     * to call from the server thread. Called for every live dragon when a player dies, since a death mid fight should
     * disqualify the player for every dragon that is live at that moment.
     */
    public static void recordDeath(UUID dragonId, UUID player)
    {
        if (dragonId == null || player == null)
            return;
        DEATHS.computeIfAbsent(dragonId, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    /** The player UUIDs that recorded any damage on this dragon (empty if none). A copy for safe iteration. */
    public static Set<UUID> contributors(UUID dragonId)
    {
        Map<UUID, Double> map = DAMAGE.get(dragonId);
        return map == null ? Set.of() : new java.util.HashSet<>(map.keySet());
    }

    /**
     * The player who dealt the STRICTLY highest damage to this dragon and did NOT die during its fight, or null when
     * nobody qualifies (no damage recorded, or every contributor died). Tie-break: on an exact damage tie the lowest
     * UUID wins, so the award is deterministic and never silently dropped on a tie. Players in the death set for this
     * dragon are excluded before the comparison, so a top-damage player who died cannot win.
     */
    public static UUID topSurvivor(UUID dragonId)
    {
        Map<UUID, Double> map = DAMAGE.get(dragonId);
        if (map == null || map.isEmpty())
            return null;
        Set<UUID> died = DEATHS.getOrDefault(dragonId, Collections.emptySet());

        UUID best = null;
        double bestAmount = -1.0;
        for (Map.Entry<UUID, Double> e : map.entrySet())
        {
            UUID player = e.getKey();
            if (died.contains(player))
                continue; // died during the fight: disqualified
            double amount = e.getValue();
            if (amount > bestAmount || (amount == bestAmount && (best == null || player.compareTo(best) < 0)))
            {
                best = player;
                bestAmount = amount;
            }
        }
        return best;
    }

    /**
     * Start-of-fight reset for a dragon: drop any stale damage and death records so a re-used entity UUID or a spawn
     * that follows a previous fight cannot leak the earlier fight's accounting. Called from the spawn path.
     */
    public static void clearFight(UUID dragonId)
    {
        if (dragonId == null)
            return;
        DAMAGE.remove(dragonId);
        DEATHS.remove(dragonId);
    }

    /** Stop tracking a dragon and free its maps. Call once its death has been processed. */
    public static void clear(UUID dragonId)
    {
        if (dragonId != null)
        {
            DAMAGE.remove(dragonId);
            DEATHS.remove(dragonId);
        }
    }
}
