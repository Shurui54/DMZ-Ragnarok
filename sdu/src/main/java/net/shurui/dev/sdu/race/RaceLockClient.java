package net.shurui.dev.sdu.race;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// client-only cache of prestige-gated race state, filled by SU and read by RaceSelectionScreenMixin to
// grey out + lock races the local player hasn't unlocked. FIXED cross-mod contract: SU (AFTER, optional)
// calls apply() by reflection; sdu references NO SU type and does NO server-side enforcement (SU owns the
// config, the gate and the packet that fills this). all state static: per-client, client thread only. race
// ids lowercase-normalised to match DMZ.
public final class RaceLockClient {

    // race id (lowercase) -> required prestige level.
    private static final Map<String, Integer> RACE_REQUIRED = new HashMap<>();

    // race ids (lowercase) locked for the local player.
    private static final Set<String> LOCKED = new HashSet<>();

    private RaceLockClient() {
    }

    // replace the cache with SU's freshly computed state (SU calls this by reflection). args copied and
    // lowercase-normalised, nulls treated as empty; passing empties clears the lock state.
    public static void apply(Map<String, Integer> raceRequired, Set<String> lockedForLocalPlayer) {
        RACE_REQUIRED.clear();
        LOCKED.clear();
        if (raceRequired != null) {
            for (Map.Entry<String, Integer> e : raceRequired.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    RACE_REQUIRED.put(key(e.getKey()), e.getValue());
                }
            }
        }
        if (lockedForLocalPlayer != null) {
            for (String id : lockedForLocalPlayer) {
                if (id != null) {
                    LOCKED.add(key(id));
                }
            }
        }
    }

    public static boolean isLocked(String raceId) {
        return raceId != null && LOCKED.contains(key(raceId));
    }

    // 0 if none configured.
    public static int requiredLevel(String raceId) {
        return raceId == null ? 0 : RACE_REQUIRED.getOrDefault(key(raceId), 0);
    }

    // drop cached state (e.g. on disconnect).
    public static void clear() {
        RACE_REQUIRED.clear();
        LOCKED.clear();
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
