package net.shurui.shuruisutilities.saiyan;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.RandomSource;

/**
 * Stable generated saiyan look from an entity UUID, so an rgnpc whose GeckoLib model is missing draws as a random DMZ
 * saiyan instead of a plain Steve, with NO synced fields and NO packets: determinism replaces network cost. Same UUID
 * always yields the same {@link SaiyanAppearance.Roll}, so every client draws the same face.
 *
 * <p>{@code allowNamed = false}: a fallback stand-in must NEVER become one of the three named saiyans (real player skin
 * + real account name over an entity that is not that person). Always a generic custom-character saiyan.
 *
 * <p>Cached because the render loop reads each field several times per entity per frame; keyed by UUID because the
 * appearance is exposed through a stateless interface default that cannot carry a field. Growth is bounded by distinct
 * UUIDs seen in a session; entries never need invalidating (a UUID's look never changes).
 *
 * <p>Common (not client) code on purpose: touches only {@link RandomSource} and {@link SaiyanAppearance}, so the entity
 * classes implement {@link RgNpcFallbackAppearance} without pulling a client type onto the server.
 */
public final class FallbackSaiyanLook
{
    private FallbackSaiyanLook() {}

    private static final ConcurrentHashMap<UUID, SaiyanAppearance.Roll> CACHE = new ConcurrentHashMap<>();

    /** Never null. A null UUID (guarded so a getter cannot NPE on the render thread) falls back to a fixed roll. */
    public static SaiyanAppearance.Roll of(UUID uuid)
    {
        if (uuid == null)
        {
            return CACHE.computeIfAbsent(new UUID(0L, 0L), u -> roll(0L));
        }
        // fold the 128 bits into one seed: only needs to be stable per UUID and spread distinct UUIDs.
        return CACHE.computeIfAbsent(uuid, u -> roll(u.getMostSignificantBits() ^ u.getLeastSignificantBits()));
    }

    private static SaiyanAppearance.Roll roll(long seed)
    {
        // allowNamed = false: a fallback stand-in must never become a named saiyan (real player skin + name tag).
        return SaiyanAppearance.roll(RandomSource.create(seed), false);
    }
}
