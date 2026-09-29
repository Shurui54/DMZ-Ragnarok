package net.shurui.shuruisutilities.corrupted;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.subrace.SubRaces;

/**
 * Durable per-player unlock store for the shadow dragon feature. The {@code shadow_dragon} race is locked for
 * everyone by default and only opens for a player who has earned the matching unlock. Omega Shenron is no longer a
 * race: the {@code omega_shenron} entitlement now unlocks a super FORM rather than a selectable race. The grant is
 * really a race-agnostic {@code superforms} skill LEVEL (level 1), so it resolves to whichever form the player's
 * shadow dragon race defines at that level, not to {@code omega_shenron} specifically. Its entitlement is still
 * recorded here (the {@code su.raceunlock.omega_shenron} property; the id is a historical name for that skill rung).
 * The {@code shadow_dragon_form} unlock key remains defined but is unused by the current grant paths.
 *
 * <p>Storage is deliberately the SU permission-property system (the same per-UUID flatfile behind
 * {@code su.prestige.rewarded} / {@code su.characterslots.limit}), NOT DMZ's own {@code StatsData} capability: a DMZ
 * character reset wipes the capability, and an unlock earned by beating a boss must survive that wipe. Properties are
 * keyed by {@link UserIdent}, which resolves from a bare {@link UUID} even while the player is offline, so an unlock
 * can be granted to a player who has already logged off.
 *
 * <p>Values are the literal strings {@code "1"} (unlocked) / {@code "0"} or absent (locked). This class only owns the
 * state; it does not decide when to grant it. TODO(phase4b): the boss-kill grant path lives elsewhere and calls
 * {@link #grant}.
 */
public final class RaceUnlocks
{
    private RaceUnlocks() {}

    /** Unlock key for the {@code shadow_dragon} race. */
    public static final String SHADOW_DRAGON_RACE = "shadow_dragon";
    /**
     * Unlock key for the Omega Shenron entitlement. No longer a race: it records that the player earned the shadow
     * dragon super form. The grant is a race-agnostic {@code superforms} skill LEVEL (level 1), so it resolves to
     * whichever form that player's shadow dragon race defines at that level; {@code omega_shenron} is a historical name
     * for the rung, not the form every race gets. The key string and property node are kept as-is so any entitlement
     * already written survives; only what it unlocks changed.
     */
    public static final String OMEGA_SHENRON_RACE = "omega_shenron";
    /** Unlock key for a distinct shadow dragon transformation. Defined for the store; unused by current grant paths. */
    public static final String SHADOW_DRAGON_FORM = "shadow_dragon_form";

    /** Per-player property: shadow dragon race unlock. Durable per-UUID, survives a DMZ character reset. */
    public static final String SHADOW_DRAGON_RACE_PROP = "su.raceunlock.shadow_dragon";
    /**
     * Per-player property: omega shenron entitlement (now the second form rung, not a race). Durable per-UUID,
     * survives a DMZ character reset. Node name kept as-is to avoid orphaning any property already written.
     */
    public static final String OMEGA_SHENRON_RACE_PROP = "su.raceunlock.omega_shenron";
    /** Per-player property: shadow dragon transformation unlock. Set by phase 4b, read there. */
    public static final String SHADOW_DRAGON_FORM_PROP = "su.raceunlock.shadow_dragon_form";

    /**
     * Per-player pending-notice flags. When an unlock is granted while the player is offline, the matching flag is set
     * so the player is told what they earned on their next login (there is no in-game mail system). Set to {@code "1"}
     * when a notice is owed, cleared once shown. Read/written by the offline-granting path and the login handler.
     */
    public static final String PENDING_SHADOW_DRAGON_RACE_PROP = "su.raceunlock.pending.shadow_dragon";
    public static final String PENDING_OMEGA_SHENRON_RACE_PROP = "su.raceunlock.pending.omega_shenron";
    public static final String PENDING_SHADOW_DRAGON_FORM_PROP = "su.raceunlock.pending.shadow_dragon_form";

    /**
     * The race ids that are unlock-gated (locked unless the player owns the matching unlock). Lowercase. Only
     * {@code shadow_dragon} is a gated main RACE; Omega Shenron became a form rung on that same race and is no longer
     * selectable, so it is not in this set. Sub-races are gated too, but they are NOT listed here: use
     * {@link #isRaceUnlockGated(String)} / {@link #hasRaceAccess} which also cover the {@link SubRaces} sub-race axis.
     */
    public static final Set<String> UNLOCK_GATED_RACES = Set.of(SHADOW_DRAGON_RACE);

    /** Property-node prefix for every race-unlock entitlement, so a sub-race id maps to {@code su.raceunlock.<id>}. */
    public static final String RACEUNLOCK_PREFIX = "su.raceunlock.";

    private static final String TRUE = "1";

    /**
     * The shadow dragon sub-race id unlocked by defeating the dragon in {@code slot}, or null when that slot maps to no
     * sub-race. Slots run 1..7; slots 2..7 map to {@code shadow_dragon_<slot>star}. Slot 1 is the base dragon and
     * unlocks no sub-race (its kill feeds the base {@code shadow_dragon} race participation instead). The returned id is
     * only treated as a real sub-race by callers if {@link SubRaces#isSubRace(String)} agrees, so retuning the mapping
     * in {@code subraces.json} cannot grant an id that has no race folder.
     */
    public static String subRaceForSlot(int slot)
    {
        if (slot < 2 || slot > 7)
            return null;
        return "shadow_dragon_" + slot + "star";
    }

    /**
     * The property node backing an unlock key, or null for an unknown key. Covers the three fixed keys and, in the
     * fall-through, any registered {@link SubRaces} sub-race id (its node is {@code su.raceunlock.<id>}). A sub-race id
     * is only accepted when {@link SubRaces#isSubRace(String)} agrees, so an arbitrary string can never mint a node.
     */
    public static String propForKey(String key)
    {
        if (key == null)
            return null;
        String lower = key.toLowerCase(Locale.ROOT);
        switch (lower)
        {
            case SHADOW_DRAGON_RACE:
                return SHADOW_DRAGON_RACE_PROP;
            case OMEGA_SHENRON_RACE:
                return OMEGA_SHENRON_RACE_PROP;
            case SHADOW_DRAGON_FORM:
                return SHADOW_DRAGON_FORM_PROP;
            default:
                // Sub-races are entitlement-keyed by their own id, node su.raceunlock.<id>. Gated only if registered.
                if (SubRaces.isSubRace(lower))
                    return RACEUNLOCK_PREFIX + lower;
                return null;
        }
    }

    /**
     * The property node backing the race-unlock for a lowercase race id, or null if that race is not unlock-gated.
     * Only {@code shadow_dragon} is a gated race now; {@code omega_shenron} is a form rung, not a race, so it maps to
     * null here (use {@link #propForKey} for its entitlement node).
     */
    public static String racePropForRaceId(String raceId)
    {
        if (raceId == null)
            return null;
        switch (raceId.toLowerCase(Locale.ROOT))
        {
            case SHADOW_DRAGON_RACE:
                return SHADOW_DRAGON_RACE_PROP;
            default:
                return null;
        }
    }

    /**
     * The set of sub-race ids that are FREELY selectable, i.e. sub-races that exist but are not unlock-gated. Only
     * {@code half_saiyan} is free; every shadow dragon rung must be earned. Kept as an explicit allow-list so a new
     * sub-race added to {@code subraces.json} defaults to LOCKED unless it is added here on purpose.
     */
    public static final Set<String> FREE_SUB_RACES = Set.of("half_saiyan");

    /**
     * True when a race id is locked behind a per-player unlock. Covers the main gated races
     * ({@link #UNLOCK_GATED_RACES}) and every registered sub-race EXCEPT the {@link #FREE_SUB_RACES}. A plain
     * non-sub-race race (e.g. an ordinary DMZ race) is not gated here. Case-insensitive.
     */
    public static boolean isRaceUnlockGated(String raceId)
    {
        if (raceId == null)
            return false;
        String lower = raceId.toLowerCase(Locale.ROOT);
        if (UNLOCK_GATED_RACES.contains(lower))
            return true;
        return SubRaces.isSubRace(lower) && !FREE_SUB_RACES.contains(lower);
    }

    /**
     * True when {@code player} may select {@code raceId}: either the race is not unlock-gated, or the player owns the
     * matching entitlement. This is the single access decision the server-side commit gate uses for both the main
     * gated race and sub-races. Any error reads as no-access (false) so a fault fails closed. Case-insensitive.
     */
    public static boolean hasRaceAccess(ServerPlayer player, String raceId)
    {
        if (raceId == null)
            return true;
        if (!isRaceUnlockGated(raceId))
            return true;
        return has(player, raceId.toLowerCase(Locale.ROOT));
    }

    /** True when {@code player} owns the unlock for {@code key}. Any error reads as locked (false). */
    public static boolean has(ServerPlayer player, String key)
    {
        if (player == null)
            return false;
        return has(UserIdent.get(player), key);
    }

    /** Grant the unlock for {@code key} to {@code player}. No-op on an unknown key. */
    public static void grant(ServerPlayer player, String key)
    {
        if (player != null)
            grant(UserIdent.get(player), key);
    }

    /** Revoke the unlock for {@code key} from {@code player}. No-op on an unknown key. */
    public static void revoke(ServerPlayer player, String key)
    {
        if (player != null)
            revoke(UserIdent.get(player), key);
    }

    /**
     * True when the player with {@code uuid} owns the unlock for {@code key}, even offline. {@link UserIdent#get(UUID)}
     * resolves a persistent ident from the UUID alone, so this works without the player online. Any error reads locked.
     */
    public static boolean has(UUID uuid, String key)
    {
        if (uuid == null)
            return false;
        try
        {
            return has(UserIdent.get(uuid), key);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Grant the unlock for {@code key} to the player with {@code uuid}, even while they are offline. */
    public static void grant(UUID uuid, String key)
    {
        if (uuid == null)
            return;
        try
        {
            grant(UserIdent.get(uuid), key);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Revoke the unlock for {@code key} from the player with {@code uuid}, even while they are offline. */
    public static void revoke(UUID uuid, String key)
    {
        if (uuid == null)
            return;
        try
        {
            revoke(UserIdent.get(uuid), key);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** True when the raw boolean property {@code node} is set to {@code "1"} for {@code uuid} (offline-safe). */
    public static boolean flag(UUID uuid, String node)
    {
        if (uuid == null || node == null)
            return false;
        try
        {
            return TRUE.equals(APIRegistry.perms.getUserPermissionProperty(UserIdent.get(uuid), node));
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Set the raw boolean property {@code node} to {@code value} for {@code uuid} (offline-safe). */
    public static void setFlag(UUID uuid, String node, boolean value)
    {
        if (uuid == null || node == null)
            return;
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(UserIdent.get(uuid), node, value ? TRUE : "0");
        }
        catch (Throwable ignored)
        {
        }
    }

    /** True when {@code ident} owns the unlock for {@code key}. */
    public static boolean has(UserIdent ident, String key)
    {
        String node = propForKey(key);
        if (ident == null || node == null)
            return false;
        try
        {
            return TRUE.equals(APIRegistry.perms.getUserPermissionProperty(ident, node));
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Grant the unlock for {@code key} to {@code ident}. No-op on an unknown key. */
    public static void grant(UserIdent ident, String key)
    {
        String node = propForKey(key);
        if (ident == null || node == null)
            return;
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(ident, node, TRUE);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Revoke the unlock for {@code key} from {@code ident} (writes {@code "0"}). No-op on an unknown key. */
    public static void revoke(UserIdent ident, String key)
    {
        String node = propForKey(key);
        if (ident == null || node == null)
            return;
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(ident, node, "0");
        }
        catch (Throwable ignored)
        {
        }
    }
}
