package net.shurui.shuruisutilities.prestige.client;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * SU-local client cache of the prestige-gated races currently LOCKED for the local player, filled from the same
 * {@link net.shurui.shuruisutilities.prestige.PacketRaceLockSync} that also feeds sdu's own {@code RaceLockClient}.
 *
 * <p>SU keeps its own copy (rather than reading sdu's cache) because the sub-race screen must independently refuse to
 * open for a prestige-locked race, and SU cannot classload an sdu type. sdu still owns the on-screen padlock veil and
 * greying; this copy exists only so SU's {@code selectRace} injector can decide whether to open the sub-race screen.
 *
 * <p>Fail-safe direction here is the OPPOSITE of {@link net.shurui.shuruisutilities.corrupted.client.RaceUnlockClient}:
 * a race not present in the locked set reads as NOT locked, so the sub-race screen still opens when prestige data is
 * missing (never hides a race the player is entitled to). The actual selection commit is enforced server-side by
 * {@code MixinDmzCreateCharacterRaceGate}, so a false "not locked" here cannot let a locked race through.
 */
public final class RaceLockLocalClient
{
    private RaceLockLocalClient() {}

    private static volatile Set<String> locked = new HashSet<>();

    /** Replace the locked-race set with the server's freshly computed one. Null clears it. Ids lowercased. */
    public static void apply(Set<String> lockedForLocalPlayer)
    {
        Set<String> next = new HashSet<>();
        if (lockedForLocalPlayer != null)
        {
            for (String id : lockedForLocalPlayer)
                if (id != null)
                    next.add(id.toLowerCase(Locale.ROOT));
        }
        locked = next;
    }

    /** True when {@code raceId} is prestige-locked for the local player. Unknown reads as NOT locked. Case-insensitive. */
    public static boolean isLocked(String raceId)
    {
        if (raceId == null)
            return false;
        return locked.contains(raceId.toLowerCase(Locale.ROOT));
    }

    /** Drop the cache (e.g. on disconnect). */
    public static void clear()
    {
        locked = new HashSet<>();
    }
}
