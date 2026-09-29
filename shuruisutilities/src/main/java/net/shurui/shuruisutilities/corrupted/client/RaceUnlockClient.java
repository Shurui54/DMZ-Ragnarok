package net.shurui.shuruisutilities.corrupted.client;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Client-side cache of the race-unlock entitlements the LOCAL player owns, pushed by
 * {@link net.shurui.shuruisutilities.corrupted.network.PacketRaceUnlockSync}. The DMZ race-select filter mixin and the
 * SU sub-race screen read this to decide which gated races (the base {@code shadow_dragon} and the shadow dragon
 * sub-races) to show; {@code half_saiyan} is free so it is never gated here.
 *
 * <p><b>Fail-safe.</b> Until the packet has been received this cache reports NOTHING as unlocked. That is deliberate: on
 * a single-player world, an older server, or any moment before the sync arrives, gated races must stay hidden so the
 * secret is not leaked by a missing packet. A vanilla-plus-DMZ client that never gets the SU packet therefore sees
 * exactly the base DMZ races and no shadow dragon content, which is the correct fallback.
 *
 * <p>All state is static, mutated only on the client network thread's enqueued work and read only on the client render
 * thread; the volatile snapshot swap keeps reads consistent. Ids are lowercased with {@link Locale#ROOT} to match how
 * DMZ normalises race names.
 */
public final class RaceUnlockClient
{
    private RaceUnlockClient() {}

    // The set of gated race ids the local player owns. null means "no data yet" (fail closed). Swapped atomically.
    private static volatile Set<String> unlocked = null;

    /**
     * Replace the cache with the server's freshly computed unlock set for this player. A null argument is treated as an
     * empty set (received, but nothing unlocked), which is distinct from the pre-sync {@code null} state above.
     */
    public static void apply(Set<String> ids)
    {
        Set<String> next = new HashSet<>();
        if (ids != null)
        {
            for (String id : ids)
                if (id != null)
                    next.add(id.toLowerCase(Locale.ROOT));
        }
        unlocked = next;
    }

    /**
     * True when the local player owns the unlock for {@code raceId}. Returns false when no sync has arrived yet (fail
     * closed) so a missing packet can never reveal a gated race. Case-insensitive.
     */
    public static boolean isUnlocked(String raceId)
    {
        if (raceId == null)
            return false;
        Set<String> snap = unlocked;
        if (snap == null)
            return false;
        return snap.contains(raceId.toLowerCase(Locale.ROOT));
    }

    /** True once the server has sent at least one sync this session. Used only for diagnostics; reads still fail closed. */
    public static boolean hasData()
    {
        return unlocked != null;
    }

    /** Drop the cache (e.g. on disconnect) so the next session starts fail-closed again. */
    public static void clear()
    {
        unlocked = null;
    }
}
